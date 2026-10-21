---
schema: test-intent.v1
task: t-20261019-b81d05
author-role: builder
author-session: builder-index-deletes-receipt-refusal-20261021
---

# A receipt that cannot be written refuses the native handoff before the old owner closes

The builder does not supply acceptance. A native reload records each settled watcher DELETE
waiting on its mutation fence as a durable receipt before the old owner closes. A failed receipt
write was logged and the handoff went on to close the old owner, so a process death then lost the
delete. A failed write now refuses the handoff: the reload fails, the old owner is not closed and
stays the serving owner, and the waiting DELETE, still unacknowledged, reaches the old owner once
the fence is restored, as on any failed handoff. The live Green start (migration) refuses the same
way, before its irreversible step.

The new check extends the real Engine reload fixture (RuntimeReloadProducersHttpIntegrationTest).
The store refusal is injected by a delegating queue that refuses only the owner's next watcher
receipt write. Fail-before ran against the previous candidate, d4a380818.

```json
{
  "entries": [{
    "items": ["modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java"],
    "class": "New",
    "repaired": true,
    "behaviour": "Through the real Engine, its admin reload HTTP handler, native filesystem watching, SQLite and Lucene search: a watched file is deleted and its DELETE settles while a native reload holds the mutation fence, and the old owner's next watcher receipt write is refused. The old owner is not closed and stays the serving owner, the reload answers 500 with the replacement failure, the released DELETE is then recorded as a receipt by the old owner, and after a retried reload the deleted file is absent from search, its sibling stays searchable and no receipt remains.",
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
    "evidence": "gates/test-intent/evidence/b81d05-lane-b-receipt-refusal.json",
    "newChecks": [
      "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java#refusedReceiptWriteKeepsTheOldOwnerAndTheWaitingDelete"
    ]
  }]
}
```
