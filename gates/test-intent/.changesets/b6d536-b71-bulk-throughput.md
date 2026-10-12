---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b71-bulk-throughput
---

# Bulk indexing throughput: declared parents and per-core completeness rows

Independent verifier acceptance remains pending. The private execution adapter changes only process identity guarding, private temporary paths and junction-safe worktree handling; repository governance tooling and existing test assertions are unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BulkWriteChunkDeclarationTest.java"
      ],
      "class": "New",
      "behaviour": "The production bulk writer declares each parent's chunk set in the parent document it writes: indexing 43 parents (3 chunked) refreshes the searcher at most once, leaves no superseded parent version, and every parent still certifies its complete chunk set and unchanged-file admission.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-12 (b71 builder brief)",
          "quote": "Writing a parent must not need a blocking refresh or a read-back per document"
        }
      ],
      "conflictSearch": "ChunkCompletenessAdmissionTest, ChunkUnchangedAdmissionTest, LegacyChunkBackfillTest, ChunkAdmissionCostTest, ChunkAdmissionRefreshRetentionTest and RecoverableChunkCompletenessTest retain their expectations; declaration semantics and stored fields are unchanged.",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BulkWriteChunkDeclarationTest.java#bulkWritesNeedNoPerDocumentRefreshOrParentRewrite"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b71-bulk-throughput.json",
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessReopenCostTest.java"
      ],
      "class": "New",
      "behaviour": "Completeness preparation on searcher reopen reads only segments the cache has not seen: adding one parent reads at most its own rows, a reopen that only adds deletions reads no stored rows, and completeness stays correct for the deleted chunk.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-12 (b71 builder brief)",
          "quote": "Any completeness work on searcher reopen must cost in proportion to what changed, not to the index size."
        }
      ],
      "conflictSearch": "ChunkCompletenessAdmissionTest, ChunkAdmissionCostTest and ChunkAdmissionRefreshRetentionTest retain their expectations; liveness is applied from each reader, never cached.",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessReopenCostTest.java#reopenReadsOnlyNewSegmentsAndDeletionOnlyReopensReadNothing"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b71-bulk-throughput.json",
      "repaired": true
    }
  ]
}
```
