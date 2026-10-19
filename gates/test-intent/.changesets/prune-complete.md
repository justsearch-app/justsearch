---
schema: test-intent.v1
task: TASK-ID-FROM-ORCHESTRATOR
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent entries: a directory sync removes every row of a deleted file, and nothing else

Two new test classes; no existing test is changed. Sources are the owner-adopted scenarios R1-R11 of
this task. Checks pinning repaired behaviour carry an assertion fail-before on origin/main
(run-evidence.mjs --before origin/main); the close-race check is preservation (R11) and passes
before and after. D4 files are written by run-evidence.mjs. No acceptance is recorded by the
builder.

```json
{
  "entries": [
    {
      "items": ["modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "With background refresh suspended: a prune after a re-index that replaced chunk ids leaves no parent or chunk of the deleted file in the committed index; a file committed but never seen by the search view is removed or the run is not complete; a file recreated, made uncertain or renamed between the scan and the batch's application keeps all four rows (under its current path) and gets no deletion mark, and an uncertain file makes the run INCOMPLETE; searches acquired right after the prune (document, text, dense, sparse) and after a reopen see no row of the deleted file while a live file keeps its rows; a search view that cannot be refreshed makes the prune fail rather than report success.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "TASK-ID-FROM-ORCHESTRATOR", "scenario": "R1", "quote": "Re-index replaces chunk ids before a prune: one sync removes every qualifying parent/chunk row (committed index inspected)", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "TASK-ID-FROM-ORCHESTRATOR", "scenario": "R2", "quote": "Parent and children wholly absent from the old view: the sync discovers them, or reports reconciliation incomplete — never success with them left", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "TASK-ID-FROM-ORCHESTRATOR", "scenario": "R4", "quote": "File reappears, becomes uncertain, or its indexed path changes between observation and flush: current rows kept, no deletion mark", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "TASK-ID-FROM-ORCHESTRATOR", "scenario": "R7", "quote": "With background refresh suspended, newly acquired document, text, dense and sparse searches show no deleted-file rows after success; reopen confirms persistence", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "TASK-ID-FROM-ORCHESTRATOR", "scenario": "R11", "quote": "Refresh failure or close race gives a non-success outcome without leaked resources or deadlock", "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/prune-complete-repaired.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java#reindexBeforeThePruneLeavesNoChunkOfTheDeletedFile",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java#aFileWrittenAfterTheLastRefreshIsFoundOrTheRunIsIncomplete",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java#aFileThatReappearsBeforeTheBatchIsAppliedKeepsItsRowsAndGetsNoMark",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java#aFileThatBecomesUncertainBeforeTheBatchIsAppliedKeepsItsRows",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java#aFileRenamedBeforeTheBatchIsAppliedKeepsItsRowsUnderTheNewPath",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java#searchesStartedRightAfterThePruneAndAReopenSeeNoRowOfTheDeletedFile",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java#aSearchViewThatCannotBeRefreshedEndsThePruneWithoutSuccess"
      ]
    },
    {
      "items": ["modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java"],
      "class": "New",
      "behaviour": "A runtime closed while a prune is running ends the prune with an exception (no success), no deletion mark, within 30 s, and the close itself does not wait for the prune.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "TASK-ID-FROM-ORCHESTRATOR", "scenario": "R11", "quote": "Refresh failure or close race gives a non-success outcome without leaked resources or deadlock", "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/prune-complete-preserve.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java#aRuntimeClosedDuringThePruneEndsItWithoutSuccessOrHang"
      ]
    },
    {
      "items": ["modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "With the indexing loop's file-mutation fence held by a writer mid-file (parent and one chunk written, two chunks to come), a sync or a direct prune of the deleted file waits for the fence, then reports 4 rows removed and leaves none in the committed index; a VDU result replacing chunks one at a time is likewise waited for and none of its chunks survive the sync; a rename (UpdateDocumentPaths) waits for the fence before rewriting rows.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "TASK-ID-FROM-ORCHESTRATOR", "scenario": "R3", "quote": "Primary ingestion or VDU emits chunks during the prune: deterministic barriers prove no late child survives a reported deletion", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "TASK-ID-FROM-ORCHESTRATOR", "scenario": "R4", "quote": "File reappears, becomes uncertain, or its indexed path changes between observation and flush: current rows kept, no deletion mark", "label": "agent-drafted, owner-adopted" },
        { "kind": "owner-adopted-scenario", "task": "TASK-ID-FROM-ORCHESTRATOR", "scenario": "R10", "quote": "Counts and deletion notifications follow their documented semantics (today \"files deleted\" counts row submissions: parent + 3 chunks = 4; record, do not silently change)", "label": "agent-drafted, owner-adopted" }
      ],
      "evidence": "gates/test-intent/evidence/prune-complete-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java#aSyncRemovesChunksWrittenWhileItRuns",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java#aDirectPruneRemovesChunksWrittenWhileItRuns",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java#aSyncRemovesChunksAVduResultWritesWhileItRuns",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java#aRenameWaitsForTheFence"
      ]
    }
  ]
}
```

## Acceptance records
