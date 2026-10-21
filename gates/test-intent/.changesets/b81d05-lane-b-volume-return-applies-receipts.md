---
schema: test-intent.v1
task: t-20261019-b81d05
author-role: builder
author-session: builder-index-deletes-volume-return-receipts-20261021
---

# A root whose volume returns has its pending watcher receipt applied

The builder does not supply acceptance. A root whose recorded volume is not at its path has its
watch held; a walk or sync that finds the volume back registers the watch. Its pending watcher
receipt stayed pending until a later watch call, start or reload: the sync that noticed the return
removed the deleted file from search, but the receipt was never applied. Once the held watch
resumes, the root's pending receipts are now queued on the watcher's reconcile thread and applied
through the current writer, outside the walk or sync that resumed it.

The new check extends the real Engine reload fixture (RuntimeReloadProducersHttpIntegrationTest).
Fail-before ran against the previous candidate, 35ffcf3e8.

```json
{
  "entries": [{
    "items": ["modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java"],
    "class": "New",
    "repaired": true,
    "behaviour": "Through the real Engine, native filesystem watching, SQLite and Lucene search: a person adds a root and a file under it is deleted while a durable watcher receipt for the root is pending; the root's recorded volume is then not at its path, so a later watch registration is held and the receipt waits. The recorded volume comes back and the Worker's directory sync (the periodic sync's call) finds it, with no watch call: within the fixture's 15 s bound the deleted file is absent from search, its sibling stays searchable and no receipt remains.",
    "sources": [
      {
        "kind": "owner-adopted-scenario",
        "task": "t-20261019-b81d05",
        "scenario": "I8",
        "quote": "A watcher DELETE settled while the runtime is being swapped is never reported done without its effect. After the swap, the successor reconciles the root and the rows are gone. If the swap fails or is retried, the delete is still applied once a runtime is available. Throwing without a retry is not enough.",
        "label": "agent-drafted, owner-adopted"
      },
      {
        "kind": "owner-adopted-scenario",
        "task": "t-20261019-b81d05",
        "scenario": "I8",
        "quote": "Covers a failed opener, a failed service reconstruction, consecutive swaps, and process death before reconciliation finishes. Obligations are kept until their effects are confirmed. Root removal and collection attribution are respected, and an unwatched root is never revived.",
        "label": "agent-drafted, owner-adopted"
      }
    ],
    "evidence": "gates/test-intent/evidence/b81d05-lane-b-volume-return-applies-receipts.json",
    "newChecks": [
      "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java#receiptForARootWhoseVolumeReturnsIsAppliedWithoutAWatchCall"
    ]
  }]
}
```
