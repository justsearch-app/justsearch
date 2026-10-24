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
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineNativePointerBootMutationTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootRetirementTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "Decode the versioned prefix receipt and assert its exact scope with no exclusions instead of comparing its encoded payload to an obsolete bare string. Drain/remove the declared root in both parameterized variants. All operation ordering, mutation, durable revision, cancellation, reopen, producer-drain and removal assertions remain. Share the existing fixture with the new undeclared-parent regression check by giving its four-argument factory and record package visibility; remove the redundant removalRoot component.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "removal targets exactly the declared folder. Removing a folder nobody declared is a no-op, even when declared folders lie inside it, because a repeated or retried removal must never retire a folder the first one kept.",
          "location": "task t-20261024-ci-921 user turn 2"
        }
      ]
    }
  ]
}
```

New check (kind: New, repaired contract): `EngineUndeclaredParentRemovalTest.undeclaredParentPreservesDeclaredChildAndRecordedJobs`. The owner decision above requires undeclared-parent removal to return zero and preserve the child declaration in memory and on disk, watcher registration, recorded queue state, and index contents. The check uses real Engine calls, SQLite admissions and a watcher registry, and asserts that no worker or index mutation is issued. This is an isolated new test file (exempt from the changed-existing-check gate), rather than an alteration of an existing oracle. On main 9c533e8e8 the identical new check fails with an assertion: undeclared-parent removal returns 3 instead of 0. It passes on the candidate (14 focused engine checks, zero failures and skips). Logs and JUnit XML are retained in the delivery evidence.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-nested-1",
  "verdict": "accept",
  "digest": "sha256:f5b793741d7330eeddec355b7d75d1bf5bcbd413eb8fe0eb2dfafa8ad296a3b0"
}
```
