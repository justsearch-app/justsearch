---
schema: test-intent.v1
task: t-20261023-865edf
author-role: builder
author-session: 01a1836e-616b-7623-b32e-c5434483a7d4
---

# Test-intent entries

The isolated new test files exercise the delegated scenarios in the builder brief.
The gate exempts these isolated new test sources. They pin S1/S1b/S2/S4 and preserve S3,
using the persisted queue, extraction, Lucene parent/chunk search and reopen, reconciliation
and the real Methvin duplicate filter. Their execution evidence is generated separately by the
orchestrator after committing the candidate. The existing test edits below preserve their oracles.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OriginBoundaryAdmissionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UnreadableAndReplacedSourceQueueTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Stubs and verifications of the ordinary admission check now call the size-aware overload with a third long argument. All existing inputs, outcomes, force-path interactions, ordering, and source-authority assertions remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "S1/S2 compare stored size and mtime; S3 preserve unchanged and incomplete-chunk admission; S4 repair missing stored size once; S5 preserve forced and collection-mismatch admission; S7 retain unavailable-source content.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "S7: admit an unreadable or replaced-volume source through UnreadableAndReplacedSourceQueueTest; extraction and destructive publication must remain deferred while indexed content survives. This entry changes only the ordinary stamp-check signature; source authority expectations are retained."
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsCandidateDiscoveryTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingDocumentOpsScalarIntegrationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LegacyChunkBackfillTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SplitSurrogateQueuedRestartTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Windows1252QueuedRestartTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Indexed-file fixtures now supply the existing source size and mtime, preserving their unchanged-source precondition under S4. Legacy quality-score, encoding-policy, chunk-declaration, vector-preservation and forced-admission expectations are retained. Reconciliation expectations, caps, cancellation, identity guards, exclusions, and platform conditions remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "S1/S2 compare stored size and mtime; S3 preserve unchanged and incomplete-chunk admission; S4 repair missing stored size once; S5 preserve forced and collection-mismatch admission; S7 retain unavailable-source content.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
