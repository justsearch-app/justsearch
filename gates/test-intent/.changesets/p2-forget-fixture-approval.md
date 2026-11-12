---
schema: test-intent.v1
task: p2-forget-fixture-approval
author-role: builder
author-session: p2-forget-fixture-approval-dlx1d4je
---

# Forget runtime fixture uses a watched-folder reindex walk

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForgetQueueRuntimeTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The shared fixture's recorded walk kind changed from an import (OperationKind.INGEST, core.ingest-files) to a watched-folder reindex walk (OperationKind.REINDEX, core.reindex), matching the non-import operation used by RecordedIngestionCoordinatorTest.dispatchesAcceptedWatchedReindexThroughMembershipFencedProducer. The old fixture directly accepted an import without the prepared approval required by product imports, so both cases failed during enqueueRecordedEntries at line 69 before forget. The non-import walk restores unfinished recorded coverage without weakening the import approval boundary or adding enumeration control. Both Q55QueueTryUnwindsRuntimeDuringActualInlineOverflowRetirement and Q55FileTryUnwindsRuntimeDuringActualInlineOverflowRetirement retain every assertion and expected value, their manual enqueue assertion, unfinished walk, real stream overflow during forget, issued serving-lease retirement, owner locks and committed callbacks. Forget during an import walk is no longer covered by this test.",
      "evidence": "gates/test-intent/evidence/p2-forget-fixture-approval.json"
    }
  ]
}
```
