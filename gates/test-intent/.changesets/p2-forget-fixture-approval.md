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

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-85_zu4rd-b250-20261112",
  "verdict": "accept",
  "digest": "sha256:60072e3d0199c052dbc0594190beae8dbd966fcde9bcff0c74505d2d68149d76",
  "note": "The settled 2026-11-12 decision permits this reindex fixture. The single changed operation kind preserves every Q55 assertion; both candidate Q55 cases executed and passed. Import-walk forget remains outside these two checks."
}
```
