---
schema: test-intent.v1
task: t-20261024-ci-921
author-role: builder
author-session: codex-nested-removal-ci-921
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/PeriodicCycleRemovedRootTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootCompletionMembershipTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WatchedRootsStateTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WatchedRootsStatusCounterTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Remove both obsolete removeRootAndNested overloads after exact-declaration removal displaced their last production caller. Tests now exercise the production removeRootAndPersist method. The former parent-and-child cleanup test explicitly removes each declaration, retains every cleanup and sibling assertion, and additionally asserts the child survives removal of the parent. All status counter, persistence, membership, completion-race and snapshot assertions remain intact.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Remove the unused method (watch for cascades: the next overload may lose its caller) rather than adding it to the frozen store.",
          "location": "task t-20261024-ci-921 request"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261023-nested-removal",
          "scenario": "D1",
          "quote": "after removing P, C keeps its documents, chunks, collection, watcher (or its awaiting-volume hold), registration, volume and pending-seeding records, reconciliation receipts and pending queue work; a C producer submitted before the removal and a C file change after it both complete. P's own documents outside C are gone.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/NestedRemovalRecoveryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Remove unused parent and child constructor parameters and their arguments. Root declarations, durable cuts, recovery, retry and all data-loss assertions remain identical.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "indexer-worker test NestedRemovalRecoveryTest.java:121, unused constructor parameters `parent` and `child`.",
          "location": "task t-20261024-ci-921 request"
        }
      ]
    }
  ]
}
```
