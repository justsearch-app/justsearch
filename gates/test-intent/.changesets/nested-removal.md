---
schema: test-intent.v1
task: t-20261023-nested-removal
author-role: builder
author-session: codex-nested-removal-20261023
---

# Test-intent entries

Skeleton written by the test-intent gate. Rules: docs/reference/testing/test-intent.md.
Give every entry a class and the fields that class needs; group items that share one warrant.
The acceptor (not the author) appends the acceptance record under the heading at the end.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootLifecycleOpsConcurrencyTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261023-nested-removal",
          "scenario": "D1",
          "quote": "after removing P, C keeps its documents, chunks, collection, watcher (or its awaiting-volume hold), registration, volume and pending-seeding records, reconciliation receipts and pending queue work; a C producer submitted before the removal and a C file change after it both complete. P's own documents outside C are gone.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "Decision records searched for nested-root removal and overlapping roots; no conflicting decision found. Existing tests expected recursive removal. The adopted v2 D1 explicitly replaces that behavior; failure/retry, producer barriers, persisted membership and collection assertions remain."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootLifecycleOpsIdempotencyTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261023-nested-removal",
          "scenario": "D1",
          "quote": "after removing P, C keeps its documents, chunks, collection, watcher (or its awaiting-volume hold), registration, volume and pending-seeding records, reconciliation receipts and pending queue work; a C producer submitted before the removal and a C file change after it both complete. P's own documents outside C are gone.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "Decision records searched for nested-root removal and overlapping roots; no conflicting decision found. Existing tests expected recursive removal. The adopted v2 D1 explicitly replaces that behavior; failure/retry, producer barriers, persisted membership and collection assertions remain."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceDocumentIdentityTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Prefix deletion receipts now encode a frozen prefix and excluded subtrees. Decode that scope when observing the receipt; retain all ordering, durability, deletion and count assertions.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "exclude the subtrees of every nested root declared when P's removal is accepted (frozen snapshot, carried in the durable receipt during migration) from all of P's prefix operations.",
          "location": "task t-20261023-nested-removal request"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Prefix deletion receipts now encode a frozen prefix and excluded subtrees. Decode that scope when observing the receipt; retain all ordering, durability, deletion and count assertions. The same-runtime test additionally asserts that an unnested root has an empty exclusion list.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "exclude the subtrees of every nested root declared when P's removal is accepted (frozen snapshot, carried in the durable receipt during migration) from all of P's prefix operations.",
          "location": "task t-20261023-nested-removal request"
        }
      ]
    }
  ]
}
```

## Acceptance records
