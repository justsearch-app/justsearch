---
schema: test-intent.v1
task: t-20261019-24fb14
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent entries: a directory sync removes every row of a deleted file, and nothing else

Three new test classes; no existing test is changed. Sources are the owner-adopted scenarios R1-R11 of
this task. Checks pinning repaired behaviour carry an assertion fail-before
(run-evidence.mjs --before 7a65990c0, the base without the fix); the round-2 checks (verifier findings on R3 and R6) carry their fail-before on 822db4cab, the round-1 candidate, since that is where the verifier found them; the round-3 checks (verifier findings on R4 and R6) carry their fail-before on 7b277b013, the round-2 candidate; the close-race check (R11) and the round-3 re-ingest check are preservation and pass
before and after.

Accepted residual window (R4). A batch's last presence check and last root check run after every slow
step (the refresh and the gathering of the batch's rows), right before the removal and inside the
file-mutation fence. That fence holds back this product's writers, not the filesystem: a file
recreated between that last check and the removal still loses its rows, and no ordering can close
the gap without holding the file itself. The window is accepted because a directory sync walks the
folder after its prune: the walk finds the file on disk again and queues it, and the re-ingest
restores its rows. EnginePruneReappearedFileTest pins that backstop with the file recreated at the
latest point, as its removal is published. D4 files are written by run-evidence.mjs. No acceptance is recorded by the
builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "With background refresh suspended: a prune after a re-index that replaced chunk ids leaves no parent or chunk of the deleted file in the committed index; a file committed but never seen by the search view is removed or the run is not complete; a file recreated, made uncertain or renamed between the scan and the batch's application keeps all four rows (under its current path) and gets no deletion mark, and an uncertain file makes the run INCOMPLETE; searches acquired right after the prune (document, text, dense, sparse) and after a reopen see no row of the deleted file while a live file keeps its rows; a search view that cannot be refreshed makes the prune fail rather than report success.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "R1",
          "quote": "Re-index replaces chunk ids before a prune: one sync removes every qualifying parent/chunk row (committed index inspected)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "R2",
          "quote": "Parent and children wholly absent from the old view: the sync discovers them, or reports reconciliation incomplete — never success with them left",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "R4",
          "quote": "File reappears, becomes uncertain, or its indexed path changes between observation and flush: current rows kept, no deletion mark",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "R7",
          "quote": "With background refresh suspended, newly acquired document, text, dense and sparse searches show no deleted-file rows after success; reopen confirms persistence",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "R11",
          "quote": "Refresh failure or close race gives a non-success outcome without leaked resources or deadlock",
          "label": "agent-drafted, owner-adopted"
        }
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
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java"
      ],
      "class": "New",
      "behaviour": "A runtime closed while a prune is running ends the prune with an exception (no success), no deletion mark, within 30 s, and the close itself does not wait for the prune.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "R11",
          "quote": "Refresh failure or close race gives a non-success outcome without leaked resources or deadlock",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/prune-complete-preserve.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java#aRuntimeClosedDuringThePruneEndsItWithoutSuccessOrHang"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "With the indexing loop's file-mutation fence held by a writer mid-file (parent and one chunk written, two chunks to come), a sync or a direct prune of the deleted file waits for the fence, then reports 4 rows removed and leaves none in the committed index; a VDU result replacing chunks one at a time is likewise waited for and none of its chunks survive the sync; a rename (UpdateDocumentPaths) waits for the fence before rewriting rows.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "R3",
          "quote": "Primary ingestion or VDU emits chunks during the prune: deterministic barriers prove no late child survives a reported deletion",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "R4",
          "quote": "File reappears, becomes uncertain, or its indexed path changes between observation and flush: current rows kept, no deletion mark",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "R10",
          "quote": "Counts and deletion notifications follow their documented semantics (today \"files deleted\" counts row submissions: parent + 3 chunks = 4; record, do not silently change)",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/prune-complete-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java#aSyncRemovesChunksWrittenWhileItRuns",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java#aDirectPruneRemovesChunksWrittenWhileItRuns",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java#aSyncRemovesChunksAVduResultWritesWhileItRuns",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java#aRenameWaitsForTheFence"
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Round 2 (verifier findings). A root that changes while a batch's files are checked again removes nothing: all four rows kept, no deletion mark, run INCOMPLETE. A VDU result that gets the file-mutation fence right after the prune's batch (before the prune commits) leaves no chunk of the removed file in the committed index, while the sync still reports the parent and three chunks removed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "R3",
          "quote": "Primary ingestion or VDU emits chunks during the prune: deterministic barriers prove no late child survives a reported deletion",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "R6",
          "quote": "Denied subtree, unreadable source, root disappearance/swap, unavailable volume identity, deferred registration authorize no deletion; genuine deletions beside unknown entries still proceed",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/prune-complete-round2.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java#aRootSwappedWhileTheBatchIsCheckedAgainRemovesNothing",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java#aVduResultThatGetsTheFenceRightAfterTheBatchLeavesNoChunk"
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Round 3 (verifier findings). A file recreated while the batch refreshes its view, after the batch's first root check, keeps all four rows and gets no deletion mark. A root lost once the batch's removals are published makes the run INCOMPLETE, while the removals made while the root held stay committed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "R4",
          "quote": "File reappears, becomes uncertain, or its indexed path changes between observation and flush: current rows kept, no deletion mark",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "R6",
          "quote": "Denied subtree, unreadable source, root disappearance/swap, unavailable volume identity, deferred registration authorize no deletion; genuine deletions beside unknown entries still proceed",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/prune-complete-round3.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java#aFileThatReappearsWhileTheBatchRefreshesItsViewKeepsItsRows",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/PruneRemovesWholeFileTest.java#aRootLostOnceTheRemovalsArePublishedMakesTheRunIncomplete"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EnginePruneReappearedFileTest.java"
      ],
      "class": "New",
      "behaviour": "Accepted residual window, backstop: on a running engine, a file recreated as its removal is published loses its rows to the prune, the sync reports it deleted and adds it again (files added = 1), and the queued re-ingest makes it searchable again with its chunks.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "R4",
          "quote": "File reappears, becomes uncertain, or its indexed path changes between observation and flush: current rows kept, no deletion mark",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/prune-complete-preserve-round3.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EnginePruneReappearedFileTest.java#aFileRecreatedAsItsRemovalIsPublishedIsIndexedAgainByTheSync"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a15424-dde6-7413-b0ac-3d3d9a1bd403",
  "verdict": "accept",
  "digest": "sha256:d93d095f1a149fd71d6c27cf35f8c402553a97accdb2bed202fd6e773f20ae23",
  "note": "Accepted under the 2026-10-19 narrowed scope: normal sync, forced sync and direct prune; PRUNE_PREFIX replay is a separate follow-up. Round-4 independent probes and two mutations, CI run 39262400540, and real Engine HTTP text/vector deletion and live controls pass on 30aca5184."
}
```
