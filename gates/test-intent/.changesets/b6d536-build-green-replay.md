---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b6d536-build-green-20261015-codex
---

# Replay retention during chunk declaration

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/VduReplayFailureRetentionTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "A failed VDU replay retains its exact durable payload. A chunk insertion failure must not write terminal parent status or replacement content. The chunk count/content-revision declaration is permitted before insertion and is not a terminal VDU update.",
      "whySurvives": "Product inspection separates ChunkDocumentWriter.declareChunkSet (only chunk_total and chunk_parent_content_sha256) from VduResultWriter's final content/status update. The missing-parent test now verifies each exact update separately; the insertion-failure test permits exactly one declaration for this parent, rejects every other update, and rejects all unverified coordinator interactions. The fixed fixture declares 18 chunks and its independently pinned SHA-256. All original payload, reopen, injection and no-terminal-write assertions remain. The unchanged deletion-failure test still forbids every parent update. No product code changed.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "If the product is right, change the expectations to name the exact intended update (its arguments, not `any()`), and keep every assertion that the payload is retained and that no terminal parent write happens. Don't relax or remove an assertion.",
          "location": "task t-20261005-b6d536 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-replay.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/VduReplayFailureRetentionTest.java"
      ]
    }
  ]
}
```

## Acceptance records
