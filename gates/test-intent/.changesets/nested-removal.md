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
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/AcceptedFileBroadReplayTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/AcceptedProjectionBroadReplayTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Broad replay fixtures admit current scoped DELETE_PREFIX payloads before capturing their exact receipt snapshots. Prefix keys (including deliberately malformed ones), accepted ordering, document/chunk witnesses, fault injection, complete snapshot equality, retained rows and exact cleanup assertions remain unchanged. Dedicated legacy conversion tests retain raw receipts; LegacyApprovedGapReplayTest additionally pins the real stale-approval defect so this fixture adaptation cannot hide it.",
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/CommittedNativeEffectsTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "The IndexCountOps mock now calls the real four-argument prefix-count overload reached by the existing three-argument overload. Counts still come from the same real Lucene fixture through countQueryOrThrow. No expected counts, survivor sets, unexpected-document/chunk checks, exception injection or never-acknowledge assertions changed."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceVduHardeningTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The SWITCHING prefix receipt assertion uses the canonical directory-prefix key and decodes the scoped payload, asserting the same directory and an empty exclusion list. RPC outcome, buffered operation kind, required receipt existence and all other assertions remain.",
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

## K7 enumeration follow-up checks

`LegacyPrefixEnumerationTest` is an isolated new check source. It exercises the server's
migration enumeration with worker-only, held and Head-persisted nested declarations missing
from its scan plan. The receipt must freeze C's exclusion before enumeration, retain that
scope when the queue is reopened without declarations, preserve C's documents and jobs on
replay, and remove P's documents. Enumeration without declaration authority must leave a raw
receipt unconverted. These expectations follow from K7 and the owner's follow-up quoted above;
the old scan-root conversion froze an empty scope and lost C.

## Full-suite follow-up

The four classes named by the independent verifier reproduce 24 assertion/mock failures on
6e2577544 and pass unchanged on 69130b292. The input and mock adaptations above keep all
exact receipt, count, document, chunk and refusal oracles.

`LegacyApprovedGapReplayTest` is an isolated new check source. It keeps a raw legacy prefix
receipt approved as a skipped gap, then forces its durable conversion during replay. Conversion
must not apply the previously skipped delete: the document and receipt remain, the stale
approval is refused, and only a fresh exact-version approval allows cleanup while retaining
the document. The owner explicitly requires: "Never weaken an assertion that protects against
data loss" (task t-20261023-nested-removal user turn 6). The old revision check lost that skip
decision after conversion; this check is the product repair, separately from fixture adaptation.

## Acceptance records
