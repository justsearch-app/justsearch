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
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/MigrationEnumerationCompletenessTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The resumed prefix-delete enumeration fixture supplies its live Worker declaration authority to the enumeration overload. Its source root, raw legacy receipt, initial enumeration, queue deletion, resumed count and no-resurrection assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "every conversion of a legacy prefix receipt into a scoped one, including the one in migration enumeration, must freeze the exclusions for live nested roots under the prefix, the same way replay does; there must be a single conversion rule, not two.",
          "location": "task t-20261023-nested-removal user turn 5"
        }
      ]
    }
  ]
}
```

## K7 enumeration follow-up checks

`LegacyPrefixEnumerationTest` is an isolated new check source. It exercises the server's
migration enumeration with worker-only, held and Head-persisted nested declarations missing
from its scan plan. The receipt must freeze C's exclusion before enumeration, retain that
scope when the queue is reopened without declarations, preserve C's documents and jobs on
replay, and remove P's documents. Enumeration without declaration authority must leave a raw
receipt unconverted. These expectations follow from K7 and the owner's follow-up quoted above;
the old scan-root conversion froze an empty scope and lost C.

## Acceptance records
