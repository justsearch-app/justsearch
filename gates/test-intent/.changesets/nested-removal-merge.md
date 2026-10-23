---
schema: test-intent.v1
task: t-20261023-nested-removal-merge
author-role: builder
author-session: codex-nested-removal-merge-20261023
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootPresenceGuardTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsWalkSkipPolicyTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Unify nested ownership on the frozen PathPrefixScope used by deletion, replay and reconciliation. RootVolumeLedger.forgetUnder takes excluded subtree paths; the test replaces an always-true owner predicate with an empty exclusion list. SyncDirectoryOps obtains the same scope from its injected scope factory; tests omit the redundant null owner-predicate argument. Every root, file, cancellation/provenance input, collection, expected outcome and assertion is unchanged. No test is disabled or weakened.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Where the two express the same rule differently, keep one implementation, not two. Keep both sides' tests passing unchanged unless a test pins a signature the merge legitimately unifies; record any such adaptation under test-intent.",
          "location": "task t-20261023-nested-removal-merge request"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/DeletionCompletionReceiptTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "The new test from main calls the unified bufferDeleteByPathDuringSwitching method with PathPrefixScope(path, empty exclusions), instead of its removed raw-string argument. This is the same unpartitioned path input encoded by the current signature. Every verification and assertion on buffered/completed replies, success, deleted jobs, error fields and protobuf backward compatibility remains unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Where the two express the same rule differently, keep one implementation, not two. Keep both sides' tests passing unchanged unless a test pins a signature the merge legitimately unifies; record any such adaptation under test-intent.",
          "location": "task t-20261023-nested-removal-merge request"
        }
      ]
    }
  ]
}
```

## Merge preservation checks

`SyncDirectoryScopeRefreshTest` is an isolated new source preserving #910's live ownership
checks during an in-flight sync. A child declaration inserted before pruning presence is
judged must preserve the child's real Lucene document while pruning the parent's absent
file. A declaration inserted between enqueue batches must keep the child's file out of
the parent's queue admission. These checks distinguish live sync ownership from the
frozen scope of durable removal receipts; both use the same prefix scope rule. Existing
checks remain unchanged except the signature adaptations above.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-nested-3",
  "verdict": "accept",
  "digest": "sha256:87ee03acc9a8149c7dcdc180731186fdc80bcbb32ff6ad6ab3269bf6cec6ff40"
}
```
