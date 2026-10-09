---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b42-legacy-backfill-20261009-builder
---

# Legacy upgrade admission

Complete legacy parents now keep unchanged admission and acquire declarations from their stored content and verified children. The one-time parent RMW preserves existing vectors, runs under the mutation lock, and checks the current writer snapshot before publishing. It never rewrites children, extracts the source or encodes embeddings. Incomplete sets still reach extraction and the canonical writer repairs them. This supersedes the b40 expectation that even a complete legacy set must be rewritten. The separate verifier owns acceptance; no builder acceptance is supplied.

```json
{
  "entries": [
    {
      "items": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LegacyChunkBackfillTest.java"],
      "class": "New",
      "behaviour": "A complete legacy parent takes the real scan's UNCHANGED outcome, invokes no extractor, emits no job for embedding, preserves real parent/child vectors and child documents, gains count/digest declarations, and never calls the splitter again, including after index reopening. Zero-child and missing-content parents gain zero declarations. Both regression checks fail by assertion on 65daf680a. The candidate preservation run also verifies incomplete legacy admission reaches extraction and canonical replacement restores completeness.",
      "sources": [{"kind": "owner-words", "quote": "when a legacy parent passes the existing legacy completeness check, it is admitted as unchanged and its declaration is recorded from data already stored", "location": "task t-20261005-b6d536 request: b42 builder brief, 2026-10-09, item 1"}],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b6d536-batch1-legacy-backfill.json",
      "newChecks": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LegacyChunkBackfillTest.java#completeLegacyScanBackfillsWithoutExtractionOrEmbeddingAndSurvivesReopen", "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LegacyChunkBackfillTest.java#legacyZeroChildrenAndMissingContentGainDurableZeroDeclarations"]
    },
    {
      "items": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkUnchangedAdmissionTest.java"],
      "class": "New",
      "behaviour": "The legacy expectation changes from compulsory rewrite to unchanged admission with immediate backfill and no repeated splitter call. Declared admission, canonical chunked/zero-child declarations and the 5,000-child/1,000-small-file timing workloads are preserved. Fresh candidate execution includes every case.",
      "sources": [{"kind": "owner-words", "quote": "After backfill the legacy check never runs again for that parent, across restarts.", "location": "task t-20261005-b6d536 request: b42 builder brief, 2026-10-09, item 1"}],
      "evidence": "gates/test-intent/evidence/b6d536-batch1-legacy-preservation.json",
      "newChecks": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkUnchangedAdmissionTest.java"]
    },
    {
      "items": ["modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java"],
      "class": "New",
      "behaviour": "Complete legacy admission is accepted. Missing/duplicate/wrong-total/wrong-revision/content-change rejection remains. New race checks prove a stale admission snapshot cannot backfill children deleted meanwhile or overwrite a changed parent. Those package-private backfill interfaces are new, so race checks are candidate-only; fail-before uses only shared scan/admission interfaces in LegacyChunkBackfillTest.",
      "sources": [{"kind": "owner-words", "quote": "an incomplete legacy set is still not unchanged and is rewritten", "location": "task t-20261005-b6d536 request: b42 builder brief, 2026-10-09, item 1"}],
      "evidence": "gates/test-intent/evidence/b6d536-batch1-legacy-preservation.json",
      "newChecks": ["modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java"]
    }
  ]
}
```
