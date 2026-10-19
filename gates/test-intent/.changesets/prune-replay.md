---
schema: test-intent.v1
task: t-20261019-24fb14
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent entries: replayed and late prunes leave no rows of a deleted file

Stacked on #793 (prune-complete). Sources are the owner-adopted scenarios RP1-RP11 (v2) of this
task. Checks pinning repaired behaviour carry an assertion fail-before on the #793 head b8eb9217e
(run-evidence.mjs --before b8eb9217e with the compatibility patch
gates/test-intent/evidence/prune-replay-before-compat.patch: a FileMutationFence that is the plain
fair lock the loop already used and records nothing, an IndexingLoop accessor returning null, a
replay context that ignores the publisher, and a promotion overload that ignores the prune stop
point; none of the repaired behaviour). Preservation checks carry the same before run and pass on
both.

Which fence each replay path takes (RP1, RP1p). Promotion: Green's producer, the services under the
final mutation fence (KnowledgeServer, prepareSwitchReplayForPromotion with current.ingestService()).
Post-deferred-upgrade and failed-migration drains: the services whose loop is live at that point
(appServices.ingestService()). Refused-source replay: the paused candidate producer
(producer.ingestService()). Synchronous startup replay (BootDisposition.NATIVE): no publisher can
exist, because it runs before any application service, and so any indexing loop, is built
(KnowledgeServer boot path, before appServices = newAppServices()); it keeps the replay service's
root authority (recorded volume) and needs no fence.

Late publications (RP3). The loop's writer and a rename report the path whose rows they publish,
inside the fence; the prune's last pass judges those paths. A VDU result needs none: it writes
chunks only under a parent it finds inside the fence (ChunkDocumentWriter refuses without the
parent's identity), so it never publishes a path's first rows.

RP5 bound. A file recreated after its last absence check loses its rows to the batch; a replayed
prune re-probes every file it removed once its removal is committed and queues the present ones for
indexing before the drain acknowledges the prune (in exact mode it then waits for them, as after a
replayed SYNC_ROOT). PrunePrefixReplayUncertainRootTest's present-folder case now records the
root's volume through the person's add first: replay is vouched for as a direct prune is (RP4), and
only the add records a volume. Its assertions are unchanged. D4 files are written by
run-evidence.mjs. No acceptance is recorded by the builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneLatePublicationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/MigrationTransitionBarrierTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "With the indexing loop's real JobBatchWriter held inside the file-mutation fence (after the file's parent, or before its first row) and the file then deleted: a promotion replay, a best-effort replay, a replayed normal SYNC_ROOT and a replayed forced SYNC_ROOT in the promotion all leave 0 committed rows (parent and chunks) of the file once the writer finishes; so does a best-effort replay raced by a VDU result replacing chunks, and a normal sync or direct prune whose scan ran before the file's first row. The promotion keeps its PRUNE_PREFIX or SYNC_ROOT receipt under its generation; a best-effort drain removes it. A replayed prune of a folder replaced at the added path, or of a folder with no recorded volume, removes nothing (3 rows kept) and its PRUNE_PREFIX stays buffered. The harness accepts a stop point at a replayed prune's first removal, only with a self-exit; stopping there leaves the committed index with all rows of the file and every receipt, the retry removes the deleted file and keeps the live one, and finishing the promotion removes only the replayed receipts, not another generation's. A file recreated after the replayed prune committed its removal is queued for indexing before the drain acknowledges the prune. A rename held inside the fence that moves a file's parent and chunks from outside the root into it, to a destination deleted before the prune's scan, leaves 0 committed rows at the destination once the rename finishes, on a normal sync, a direct prune and a best-effort replay (whose PRUNE_PREFIX is then removed).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "RP1",
          "quote": "A PRUNE_PREFIX replayed while a publisher is active (promotion before Green pauses; post-deferred-upgrade with the newly activated loop; migration-failure best-effort drains) is raced by a late primary publication or VDU chunk replacement for a file it prunes. Once that writer finishes: zero committed parent or chunk rows for the file, in text and vector search.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "RP2",
          "quote": "Replayed normal and forced SYNC_ROOT prunes take a real fence. The temporary WorkerIngestService used for replay has no loop and no admission, so its fence wrapper runs unfenced. Raced as in RP1, with the same outcome.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "RP3",
          "quote": "A writer passes its stale check and pauses before its first publication, the file is deleted, and the prune's scan finds no rows. When the writer resumes, the file ends with zero rows after the writer finishes, either because the writer refuses or because the prune removes them. This holds on the normal sync path as well as on replay.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "RP4",
          "quote": "Replay checks the root the way normal sync does: root identity and the recorded volume, not just readability. A different readable directory at the same path, or a lost volume, means nothing is deleted for that prefix, and the replay obligation is kept (the exact operation stays buffered) for a later retry.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "RP5",
          "quote": "A later reappearance is restored by a named path (watcher, next sync or recovery), within a stated bound that a test proves. The prune removes no row whose absence is unproven. Batches confirmed earlier may stay applied. Explicit DELETE, DELETE_PREFIX, DELETE_COLLECTION and projection deletes keep their own semantics.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "RP8",
          "quote": "(a) stopped before the prune commits: a safe retry, no rows of the file left, no live rows lost;<br>(b) committed but not acknowledged: the retry is idempotent and does not remove a re-ingested file;<br>(c) after the pointer commit: exact receipt cleanup, as today.<br>Native committed-witness settlement either supports PRUNE_PREFIX with the right generation and exact revision, or refuses it safely and keeps the obligation. Add a prune-specific stop point to the existing harness barrier.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "RP10",
          "quote": "Saved state: Lucene parent and chunk contents, the switch buffer's generation, revision and order, and accepted queue revisions are all as expected after each repair case.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/prune-replay-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aPromotionReplayRemovesChunksTheLoopWritesWhileItRuns",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aBestEffortReplayRemovesAFileWhoseFirstRowsArePublishedAfterItsScan",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aBestEffortReplayRemovesChunksAVduResultWritesWhileItRuns",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aReplayedSyncRemovesChunksTheLoopWritesWhileItRuns",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aReplayedForcedSyncInThePromotionRemovesChunksTheLoopWritesWhileItRuns",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aReplayedPruneOfAFolderReplacedAtTheSamePathRemovesNothingAndStaysBuffered",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aReplayedPruneOfAFolderWithNoRecordedVolumeRemovesNothingAndStaysBuffered",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aPromotionStoppedAtThePrunesFirstRemovalLeavesASafeRetryAndExactCleanup",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aFileRecreatedAfterItsRemovalIsQueuedBeforeTheReplayEnds",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneLatePublicationTest.java#aSyncRemovesAFileWhoseFirstRowsArePublishedAfterItsScan",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneLatePublicationTest.java#aDirectPruneRemovesAFileWhoseFirstRowsArePublishedAfterItsScan",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneLatePublicationTest.java#aSyncRemovesAFileWhoseRowsARenameMovesInAfterItsScan",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneLatePublicationTest.java#aDirectPruneRemovesAFileWhoseRowsARenameMovesInAfterItsScan",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneLatePublicationTest.java#aReplayedPruneRemovesAFileWhoseRowsARenameMovesInAfterItsScan",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/MigrationTransitionBarrierTest.java#pruneReplayCutRequiresAnInstalledHarnessSelfExit"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneLatePublicationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayUncertainRootTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation, passing on the #793 head and after. A promotion replay with the loop's real writer held mid-file on a live file completes once the writer is released, removes the deleted file and keeps the live file's parent and chunks; a normal direct prune does the same for a live file written while it runs. A refused candidate replayed on the source removes the deleted file, keeps the live one and settles its receipts. A committed but unacknowledged prune retried after the file was indexed again keeps its 3 rows. Native committed-witness settlement refuses a PRUNE_PREFIX receipt and keeps it. A prune followed by an UPSERT for a file that is present keeps its rows and queues it; an explicit DELETE after a prune removes its present file while the prune removes only the deleted one and a live file keeps its rows; a VDU result before the prune gives its live file chunks, one after it for the removed file does not bring it back, and that drain keeps every receipt. A buffered prune of a missing folder removes nothing and stays buffered; of a present, added folder it removes the deleted file. A file renamed into the root during a replayed prune, and still there, keeps every row it had. A VDU result for a file the prune's first batch already removed writes no row (it finds no parent), so a direct prune leaves the file with 0 rows.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "RP1p",
          "quote": "Source-refusal replay, with admission fenced, the queue settled and the producer paused, plus synchronous startup replay with no publisher: deleted files leave no rows and live files keep theirs. These pass before and after.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "RP5",
          "quote": "A later reappearance is restored by a named path (watcher, next sync or recovery), within a stated bound that a test proves. The prune removes no row whose absence is unproven. Batches confirmed earlier may stay applied. Explicit DELETE, DELETE_PREFIX, DELETE_COLLECTION and projection deletes keep their own semantics.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "RP6",
          "quote": "No deadlock or lock-order inversion. A real JobBatchWriter is held at a latch while replay or cutover starts, then released. Completion is bounded and the test fails on a timeout, not a hang.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "RP7",
          "quote": "Order per replay mode is unchanged. Exact replay awaits preceding UPSERTs; best-effort does not, and keeps its receipts. Check UPSERT→prune, prune→an UPSERT for the reappeared file, VDU_UPDATE around a prune, and explicit deletes around a prune.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "RP8",
          "quote": "(a) stopped before the prune commits: a safe retry, no rows of the file left, no live rows lost;<br>(b) committed but not acknowledged: the retry is idempotent and does not remove a re-ingested file;<br>(c) after the pointer commit: exact receipt cleanup, as today.<br>Native committed-witness settlement either supports PRUNE_PREFIX with the right generation and exact revision, or refuses it safely and keeps the obligation. Add a prune-specific stop point to the existing harness barrier.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "RP10",
          "quote": "Saved state: Lucene parent and chunk contents, the switch buffer's generation, revision and order, and accepted queue revisions are all as expected after each repair case.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/prune-replay-preserve.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aPromotionReplayWithAHeldWriterOfALiveFileCompletesAndKeepsItsRows",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aRefusedCandidateReplayOnTheSourceRemovesTheDeletedFileAndKeepsTheLiveOne",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aCommittedButUnacknowledgedPruneRetriesWithoutRemovingAReingestedFile",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#nativeCommittedWitnessSettlementRefusesAPruneAndKeepsIt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aBufferedUpsertAfterThePruneQueuesTheReappearedFileAndKeepsItsRows",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#anExplicitDeleteAfterThePruneKeepsItsSemantics",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#vduUpdatesAroundThePruneKeepTheirOrderAndAMissedOneKeepsEveryReceipt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneLatePublicationTest.java#aFileStillPresentKeepsTheRowsItsWriterPublishedDuringThePrune",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneLatePublicationTest.java#aFileRenamedInDuringAReplayedPruneKeepsItsRows",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aVduResultAfterThePrunesRemovalOfItsFileWritesNoRow",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayUncertainRootTest.java#aBufferedPruneOfAMissingFolderRemovesNothingAndStaysBuffered",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayUncertainRootTest.java#aBufferedPruneOfAPresentFolderRemovesTheDeletedFile"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/HeldFileWriter.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/FenceRace.java"
      ],
      "class": "New",
      "behaviour": "Test-only helpers of the checks above, with no assertions of their own: HeldFileWriter is the indexing loop's real JobBatchWriter over a given runtime and fence, held at a latch before a chosen row publication after its claim and freshness checks passed; FenceRace runs work on its own thread and releases the held writer once that work waits for the file-mutation fence or has finished, within 30 s.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-24fb14",
          "scenario": "RP6",
          "quote": "No deadlock or lock-order inversion. A real JobBatchWriter is held at a latch while replay or cutover starts, then released. Completion is bounded and the test fails on a timeout, not a hang.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/prune-replay-preserve.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java#aPromotionReplayWithAHeldWriterOfALiveFileCompletesAndKeepsItsRows"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BulkWriteChunkDeclarationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "JobBatchWriter's last constructor parameter changed from a plain Lock to the loop's FileMutationFence, which also records the files a writer publishes; the test passes a new FileMutationFence() instead of a new ReentrantLock(). Inputs, observations and expected outcomes are unchanged."
    }
  ]
}
```

## Acceptance records
